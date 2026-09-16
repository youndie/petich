---
id: research-native-port
title: petich on Kotlin/Native — port research
type: research
status: active
date: 2026-09-16
---

# Research: taking petich to Kotlin/Native

petich is a distributed saga engine published as seven Maven coordinates. Six of its modules are
already Kotlin Multiplatform projects that declare exactly one target — `jvm()` — and the seventh,
`petich-postgres`, is a plain JVM module built on Exposed over JDBC. This document asks what it
costs to make the engine usable from a Kotlin/Native binary, which parts of that are a build change
and which are a second implementation, and what is honestly unknown.

It records **verified facts** (read in this repository, in a published artefact, or in a registry
listing on 2026-09-16), **decisions** with the alternative each one rejects, and **risks**. Anything
not verified is called a hypothesis and says where it will be settled.

The short version: the engine itself is already portable and nothing in it has to change; the
target list, the version catalogue, the storage layer and the guards do.

---

## 1. Verified facts

### 1.1 Every published coordinate is JVM-only, and that is a resolution failure rather than a missing feature

Verified by fetching the published Gradle module metadata from Maven Central.

| Fact | Where verified |
|---|---|
| `petich-core` 0.1.0 publishes five variants: `metadataApiElements`, `metadataSourcesElements`, `jvmApiElements-published`, `jvmRuntimeElements-published`, `jvmSourcesElements-published` | `repo1.maven.org/maven2/io/github/youndie/petich/petich-core/0.1.0/petich-core-0.1.0.module` |
| 0.1.0 is the only release on Central | `repo1.maven.org/maven2/io/github/youndie/petich/petich-core/maven-metadata.xml` |
| Six modules apply `kotlin("multiplatform")` and declare one target, `jvm()` | `petich-core/build.gradle.kts`, `petich-ktor/build.gradle.kts`, `petich-outbox-core/build.gradle.kts`, `petich-idempotency/build.gradle.kts`, `petich-scheduler/build.gradle.kts`, `petich-chronik/build.gradle.kts` |
| `petich-postgres` applies `kotlin("jvm")` | `petich-postgres/build.gradle.kts` |

**Consequence.** A Kotlin/Native consumer declaring `io.github.youndie.petich:petich-core` does not
get a library with a missing feature — it gets a build that fails in resolution with *no matching
variant*, before the first line of its own code compiles. The same sentence was written in the
neighbouring repository and paid for there ([chronik B-16](https://github.com/youndie/chronik/blob/main/docs/backlog/B-16-linux-native-target.md)).

### 1.2 The engine's own code is already portable

Verified by grep over every source set in this repository.

| Fact | Where verified |
|---|---|
| No `java.*` or `javax.*` reference in any `commonMain` | grep over `*/src/commonMain/**.kt` |
| The wall clock is a parameter, not a platform call: `PetichClock` is a `fun interface` handed to the engine | `petich-core/src/commonMain/kotlin/Petich.kt:82-89` |
| The two `System.currentTimeMillis()` calls in the repository are both in the JVM store, both suppressed against the lint rule `ktlint:kapkan:wall-clock`, both tracked as youndie/petich#20 | `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt:109`, `petich-postgres/src/main/kotlin/ExposedIdempotencyRepository.kt:45` |
| The dependencies of the six multiplatform modules are coroutines 1.11.0, serialization-json 1.11.0 and datetime 0.8.0, and all three publish `linuxX64` klibs | `gradle/libs.versions.toml`; `repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-linuxx64/1.11.0/`, `…/kotlinx-serialization-json-linuxx64/1.11.0/`, `…/kotlinx-datetime-linuxx64/0.8.0/` |
| Backoff arithmetic uses `kotlin.math.pow` and `kotlin.random.Random`; recurrence uses `kotlin.time.Instant` and `kotlinx.datetime` | `petich-core/src/commonMain/kotlin/Petich.kt`, `petich-scheduler/src/commonMain/kotlin/ScheduledJob.kt` |

**Confirmed while doing [B-02](../backlog/B-02-native-consumer-probe.md), 2026-09-16.** This was
written as an argument and is now a measurement: `linuxX64()` was added to `petich-core`, published
locally, and a separate `linuxX64` consumer resolved it, compiled against it, linked
`native-consumer-probe.kexe` and ran it. No source in `petich-core` was touched. The line was
reverted — the targets themselves are B-03 — and what the run still says nothing about is
`linuxX64Test`, because a publication compiles no test source.

**Consequence.** For the four modules that depend on nothing but coroutines, datetime and
serialization, the port is the line `linuxX64()` in a build script. That is the argument for doing
it, not a reason to expect it to be free — see §3 for what a second target is known to find.

### 1.3 `runBlocking` in a shared JVM+native source set resolves — checked in the portfolio, not assumed

`gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`, and roughly a hundred
`runBlocking` calls live in `commonTest` of the multiplatform modules. `runBlocking` is not part of
the common API of kotlinx-coroutines, so the question is whether a source set shared by `jvm` and
`linuxX64` can see it.

| Fact | Where verified |
|---|---|
| `kore-core` calls `runBlocking` from `commonMain` with targets jvm, linuxX64, linuxArm64, macosArm64 | `kore/kore-core/src/commonMain/kotlin/io/github/youndie/kore/lifecycle/RunUntilSignal.kt` |
| `chronik-core` calls it from `commonTest` with targets jvm and linuxX64 **and the same `applyDefaultHierarchyTemplate=false`** | `chronik/chronik-core/src/commonTest/kotlin/DeliveryRetryTest.kt`, `chronik/gradle.properties:6` |

**Consequence.** The existing test suites do not have to be rewritten to gain a target. This is the
one plausible blocker that turned out not to be one, and it is written down so nobody re-derives it.

### 1.4 The version catalogue pins the JVM variant of every Ktor artefact by name

| Fact | Where verified |
|---|---|
| All five Ktor entries name `-jvm` coordinates: `io.ktor:ktor-server-core-jvm`, `-content-negotiation-jvm`, `-status-pages-jvm`, `-test-host-jvm`, `io.ktor:ktor-serialization-kotlinx-json-jvm` | `gradle/libs.versions.toml` |
| The platform-agnostic coordinates publish `linuxx64` klibs at the pinned version 3.5.2: server-core, content-negotiation, status-pages, test-host, serialization-kotlinx-json | HTTP 200 on `repo1.maven.org/maven2/io/ktor/ktor-server-core-linuxx64/3.5.2/ktor-server-core-linuxx64-3.5.2.klib` and the four siblings |
| A Ktor server library in this portfolio already runs `testApplication` from `commonTest` on native targets | `kore/kore-ktor/build.gradle.kts`, `kore/kore-ktor/src/commonTest/kotlin/io/github/youndie/kore/ktor/ProbeRoutesTest.kt` |
| `petich-ktor`'s own code touches only `ktor-server-core`, `-content-negotiation`, `-status-pages` and the JSON serialization bridge | `petich-ktor/src/commonMain/kotlin/PetichRouting.kt`, `PetichFeatureConfiguration.kt` |

**Consequence.** `petich-ktor` can gain the target and keep its tests — including the routing test,
which today sits in `jvmTest` only because of a `java.util.concurrent.ConcurrentHashMap` in its fake
repository (`petich-ktor/src/jvmTest/kotlin/io/github/youndie/petich/ktor/PetichRoutingTest.kt:33`).
A `-jvm` coordinate left in the catalogue does not merely fail to help: in a multiplatform source
set it is the thing that makes the native compilation unresolvable.

### 1.5 The storage layer does not travel, and it has never been tested

| Fact | Where verified |
|---|---|
| `petich-postgres` is 506 lines across eight files: four repository implementations and four table definitions | `petich-postgres/src/main/kotlin/` |
| Every database call goes through `org.jetbrains.exposed.v1.jdbc.*` inside `withContext(Dispatchers.IO) { suspendTransaction(db) { … } }` | `ExposedPetichRepository.kt:28-33` and the same block in the three siblings |
| The SQL is ordinary: `SELECT … WHERE id`, an `UPDATE` guarded by `version = :version - 1`, `batchInsert`, an `INSERT` whose unique-violation is read as "key already claimed", `ORDER BY created_at LIMIT n`. No `FOR UPDATE`, no `SKIP LOCKED` anywhere in the repository | grep for `forUpdate`/`SKIP LOCKED` returns nothing; `ExposedPetichRepository.kt`, `ExposedOutboxRepository.kt`, `ExposedIdempotencyRepository.kt` |
| The four contracts it implements all live in multiplatform modules | `petich-core/src/commonMain/kotlin/Petich.kt:282-334` (`PetichRepository`, `OutboxAwarePetichRepository`, `SideEffectAwarePetichRepository`, `ExpiringPetichRepository`), `petich-outbox-core/src/commonMain/kotlin/OutboxRepository.kt`, `petich-idempotency/src/commonMain/kotlin/io/github/youndie/petich/idempotency/IdempotencyRepository.kt`, `petich-scheduler/src/commonMain/kotlin/SchedulerWorker.kt:19` |
| The module's only test asserts package names and index names — no storage behaviour is exercised anywhere in this repository | `petich-postgres/src/test/kotlin/io/github/youndie/petich/postgres/consumer/PublishedSurfaceTest.kt` |

**Consequence 1.** JDBC is a JVM interface rather than a protocol, so a native consumer has the
engine and nowhere to put a saga. A second store is required, and it is a real implementation rather
than a build change.

**Consequence 2, and it is the more important one.** There is nothing for a second implementation to
be measured against. Written after the fact, a comparison between two stores describes their
intersection — including whatever both get wrong. The corpus has to come first, while there is one
implementation; that is what the neighbouring repository's conformance kit bought, where it caught
three violated rules in a store that had passed everything else
(`chronik/chronik-conformance/src/commonMain/kotlin/ConformanceKit.kt`).

### 1.6 The bridge module is blocked outside this repository

| Fact | Where verified |
|---|---|
| `chronik-core-linuxx64` 0.1.0 does not exist on Central (HTTP 404); `chronik-core` 0.1.0 publishes the same five JVM-only variants petich does | `repo1.maven.org/maven2/io/github/youndie/chronik/chronik-core-linuxx64/0.1.0/…` (404), `…/chronik-core/0.1.0/chronik-core-0.1.0.module` |
| chronik's own repository already declares `linuxX64()` on core and conformance, closed as its B-16 on 2026-09-15 — merged, not released | `chronik/chronik-core/build.gradle.kts:23`, `chronik/docs/backlog/B-16-linux-native-target.md` |

**Consequence.** `petich-chronik` cannot declare a native target against a JVM-only dependency, so
the release order is fixed: chronik publishes a native variant, then petich. Merged is not released,
and the 404 above is the only form of that statement worth trusting.

### 1.7 The shared build conventions leave the target list to the module, and turn warnings into errors

| Fact | Where verified |
|---|---|
| `sborka.kmp` deliberately declares no targets — "this plugin gives what every KMP library here agrees on and leaves the target list where it was argued" | `sborka/build-logic/conventions/src/main/kotlin/io/github/youndie/sborka/kmp.gradle.kts` |
| The same convention sets `explicitApi()` and `allWarningsAsErrors = true` by default | same file |
| It adds `-Wl,--as-needed` to Linux native executables and stamps `org.gradle.jvm.version` on JVM variants only | same file |

**Consequence.** Adding a target is a one-line change per module, and every warning the new compiler
emits is a build failure rather than a note. That is an argument for landing the target on its own
branch rather than folded into a feature.

### 1.8 The guards see JVM variants and the consumer job builds a JVM consumer

| Fact | Where verified |
|---|---|
| `jvm-floor-audit.py` skips variants whose `org.jetbrains.kotlin.platform.type` is not `jvm` or absent, and requires a jar in the variant — native variants are correctly outside its subject | `tools/jvm-floor-audit.py` |
| `artifact-name-audit.py` walks every file of every variant, so klibs come under it as soon as they are published | `tools/artifact-name-audit.py` |
| `consumer-coverage-audit.py` compares the modules applying the publish convention against the coordinate list in the publish workflow — it counts modules, not variants | `tools/consumer-coverage-audit.py`, `.github/workflows/publish-snapshot.yaml` |
| The consumer job that resolves those coordinates (`proba`, called through sborka's `publish-wip.yaml`) builds a JVM consumer | `.github/workflows/publish-snapshot.yaml`, and the note in `.github/workflows/build.yaml` about proba's consumer build pinning Java 21 |

**Consequence.** After the port, half of what is published is checked by nobody: every guard in this
repository either ignores native variants by design or asks a question only a JVM consumer can
answer. The same gap is written down in chronik's B-16 as "the native half of the metadata stays
hand-checked once" — inheriting it silently is the thing to avoid.

### 1.9 There is no native consumer of petich today

| Fact | Where verified |
|---|---|
| konekt pins `petich = "0.1.0"` and consumes it from a JVM build | `konekt/gradle/libs.versions.toml:92` |
| shashki's server takes `petich-core`, `petich-postgres` and `petich-outbox-core` as plain JVM dependencies | `shashki/server/build.gradle.kts:83-85` |
| No other repository in the portfolio names an `io.github.youndie.petich` coordinate | grep over `gradle/libs.versions.toml` in `~/Documents/GitHub` and `~/IdeaProjects` |

**Consequence, and it shapes the order of the backlog.** Nothing today fails because petich is
JVM-only, so the port has to be split: the part that removes the resolution wall is cheap and can be
done now, and the part that costs a second storage implementation should wait for a consumer to name
the database it wants. Building a store for a hypothetical driver is how a module arrives with the
wrong SQL dialect in its name (chronik renamed its store one day before release for exactly that).

---

## 2. Decisions

### D1. Port by declaring targets, not by rewriting the engine

First idea: "port petich to Kotlin/Native" reads like a rewrite.
Decision: the engine is not touched. The port is a target list (§1.2), a version catalogue (§1.4), a
second storage implementation (§1.5), and the guards around publication (§1.8).

Why:

- every `commonMain` is already free of `java.*`, and the clock — the usual reason an engine is not
  portable — is a parameter already;
- the one blocker that would have forced a rewrite of the test suites, `runBlocking` in a shared
  source set, is verified not to be one (§1.3);
- the price: nothing here proves the engine *behaves* the same on a second target. That is what the
  test tasks on the new target are for, and §3 names the two places it could differ.

### D2. `linuxX64` and nothing else

Decision: one native target.

Why:

- it is where a Kotlin/Native server runs, and a server is the only thing that has ever wanted a
  saga engine;
- Apple and mingw targets add test tasks nobody runs and klibs nobody asked for, and a target with a
  test task that never executes looks like coverage without being it;
- adding a second one later is a line in the same file, and the cost of leaving it out today falls
  on nobody. Same decision, same wording, as chronik's B-16 — taken for the same reason rather than
  copied.

### D3. `petich-postgres` stays JVM-only and keeps its name

Decision: the Exposed store is not made multiplatform and is not renamed.

Why:

- JDBC does not cross to native; there is no version of that module that compiles there;
- rejected alternative — an `expect`/`actual` storage module with a JDBC actual on the JVM and a
  driver actual on native. It buys one coordinate and costs the ability to depend on either half
  alone, and the two implementations share no code beyond the SQL text;
- the price: a consumer reading the module table sees two stores and has to know which one their
  target can take. That is a documentation job ([B-14](../../backlog.md)), not a naming one.

### D4. The conformance corpus is written before the second store

Decision: the four storage contracts get a corpus of cases runnable against any implementation, and
it lands before any second implementation does.

Why:

- there are zero storage tests today (§1.5), so "the second store agrees with the first" is
  unverifiable and the first is unverified;
- rejected alternative — write the store, then diff its behaviour against Exposed. That produces the
  intersection of two implementations and calls it a contract;
- evidence rather than principle: in the neighbouring repository the corpus named three violated
  rules in a store that had passed every hand-written test, and an atomicity case caught a claim
  rewritten into two statements that the corpus itself could not see
  (`chronik/docs/backlog/B-17-sqlx4k-sqlite-store.md`).

### D5. The native store takes a driver from outside and never packs one

Decision: whatever the second store is, it depends on the database-agnostic half of sqlx4k and
receives an already-opened driver.

Why:

- a Kotlin/Native binary that links two sqlx4k drivers does not link at all — each carries its own
  Rust runtime and they define the same symbols (`duplicate symbol: std::panicking::EMPTY_PANIC`),
  paid for in a neighbouring repository and written into
  `chronik/chronik-sqlx4k-sqlite/build.gradle.kts`;
- a store that carries no driver cannot cause that collision whichever driver the application
  brings;
- the price: the application does the wiring. It already does — `petich-postgres` takes an Exposed
  `Database` and declares no driver either, for the same reason.

### D6. The bridge module waits, and the release order is stated rather than discovered

Decision: `petich-chronik` keeps `jvm()` alone until `chronik-core` publishes a native variant;
chronik releases first.

Why:

- §1.6: the dependency's native klib does not exist on Central today;
- rejected alternative — vendor the two interfaces the bridge needs. It duplicates a contract owned
  elsewhere in order to make a build green, which is the failure mode the bridge module was carved
  out to avoid.

### D7. Acceptance is a consumer that resolves, not metadata read by eye

Decision: every stage that changes what is published is accepted by a separate `linuxX64` project
that declares the coordinates from `mavenLocal` and links, not by looking at `.module` files.

Why:

- the failure being fixed is a resolution failure, and only a resolution proves it fixed;
- metadata is still worth looking at, as a second opinion — but on its own it says the variant was
  *published*, not that a consumer can *take* it;
- this is how the same claim was checked in chronik, and it is cheap: one throwaway project.

---

## 3. Risks and open questions

**Risk 1. The engine's in-process locking has only ever run under the JVM memory model.**
`PetichEngine` keeps a map of per-saga `Mutex`es guarded by another `Mutex`
(`petich-core/src/commonMain/kotlin/Petich.kt:388-392`), and the suites that exercise it run under
`runBlocking` on a single thread. On Kotlin/Native the same code runs under a different memory model
and, in a real service, on `Dispatchers.Default`, which is multi-threaded there.
Mitigation: a concurrency case that runs on both targets under a multi-threaded dispatcher —
`EngineConfigTest` already has one shape of this (`withContext(Dispatchers.Default)`,
`petich-core/src/commonTest/kotlin/EngineConfigTest.kt:227`), and it must be a case whose failure is
a wrong version count rather than a flaky timing. Item: B-05.

**Risk 2. Polymorphic serialization is the part most likely to differ.** The saga payload hierarchy
is `@Serializable abstract class` with `@SerialName` discriminators
(`petich-core/src/commonMain/kotlin/Petich.kt`), and the storage format depends on it. Hypothesis:
nothing differs, because registration is explicit on both platforms and no reflection is involved.
To be settled by the existing `commonTest` suites running green as `linuxX64Test` in B-03 — if they
do not, the finding belongs in this section rather than in a commit message.

**Risk 3 — confirmed in part, 2026-09-16 (B-03).** Of the two findings this risk was written
around, one appeared and one did not: three test names carried a comma (`Name contains illegal
characters: ","`), and no `kotlin.jvm` import was missing anywhere. The errors did arrive all at
once and all from test sources, which is the part worth keeping: a publication compiles no test
source, so the run that proved the modules portable (§1.2) could not have found them.

**Risk 3. A second target produces its errors all at once.** `allWarningsAsErrors` and
`explicitApi()` are on (§1.7), and the two findings chronik got from the same change were both in
code rather than in the build: `@JvmInline` without its import (`kotlin.jvm.*` is a default import
on the JVM and nowhere else) and a comma inside a backticked test name, which Kotlin/Native rejects
as an identifier. Mitigation: B-03 is its own change, reviewed on its own.

**Risk 4. Half of what is published becomes unchecked.** §1.8. Mitigation: B-12 — either the
consumer job gains a native consumer, or the manual probe is named in the release checklist as the
only thing standing there. What is not acceptable is inheriting the gap without writing it down.

**Risk 5. Cross-repository release order.** §1.6 and D6. Mitigation: B-15 states the order and the
version, and B-11 is blocked rather than "in progress" until the 404 becomes a 200.

**Open question 1 — which database does a native consumer store sagas in?** Postgres through
sqlx4k keeps the SQL and the semantics of the existing store; SQLite matches what a small native
service usually carries and is what the neighbouring timer library chose, for a consumer that
existed. There is no such consumer here (§1.9), and the driver decides the module's name, its SQL
dialect and its conformance run. Item: B-08, deliberately `question` rather than `open`.

**Open question 2 — is the port worth its second half at all today?** Stages 0–2 remove a resolution
wall for a cost measured in lines. Stage 3 is a real implementation with a corpus behind it. The
honest answer is that stage 3 waits for question 1 to have an owner, and this document says so
rather than letting the backlog imply a schedule.

---

## 4. What happens next

The order and the acceptance criteria are in [backlog.md](../../backlog.md). The first three things,
in order, because each removes a reason the next one cannot be checked:

1. the documentation gate and a consumer probe that **fails today** ([B-01](../backlog/B-01-docs-gate.md),
   [B-02](../backlog/B-02-native-consumer-probe.md)) — a port with no negative control cannot tell a
   fix from a coincidence;
2. `linuxX64` on the four modules that depend on nothing but coroutines, datetime and serialization
   ([B-03](../backlog/B-03-linux-target-on-the-portable-four.md));
3. the conformance corpus, while there is still exactly one storage implementation to write it
   against ([B-07](../backlog/B-07-storage-conformance-corpus.md)).
