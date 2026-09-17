# petich — backlog: the Kotlin/Native port

> Document role: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. This page holds the generated index and
> everything that is not an item: the goal, the stages, the decisions.
>
> A new item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.

## The goal

Make petich usable from a Kotlin/Native binary. Today it is not usable there in the strict sense:
every published coordinate carries JVM variants only, so a native consumer's build fails in
resolution — *no matching variant* — before its own first line compiles.

The work splits into two halves that cost very different things, and the split is the point of this
backlog:

* **A build change.** The engine is already portable — no `java.*` in any `commonMain`, the clock is
  a parameter, and the one plausible blocker (`runBlocking` in a source set shared by jvm and native)
  is verified not to be one. Four modules need one line each; the Ktor module needs that line and
  five coordinates in the version catalogue that stop naming `-jvm` artefacts.
* **A second implementation.** Storage does not travel. `petich-postgres` is Exposed over JDBC, and
  JDBC is a JVM interface rather than a protocol. A native consumer gets the engine and nowhere to
  put a saga until a second store exists — and before that store is written, the contract it has to
  satisfy has to exist as something runnable, because today there are zero storage tests in this
  repository.

Everything here rests on [docs/research/research-native-port.md](docs/research/research-native-port.md):
what was read where, which decisions were taken and what each of them rejected. Read it first.

## The reality check

**Nothing fails today because petich is JVM-only.** Its two consumers, konekt and shashki, are JVM
builds, and no other repository in the portfolio names a petich coordinate. That is not an argument
against the port — it is the reason for the order below. Stages 0–2 remove a resolution wall for a
cost measured in lines and can be done now. Stage 3 is a real implementation, and its first item is a
question for the owner ([B-08](docs/backlog/B-08-which-database-for-the-native-store.md)): a store
named after a driver nobody asked for arrives with the wrong dialect in its name.

Three things that are not obvious from the items:

* **[B-02](docs/backlog/B-02-native-consumer-probe.md) must fail before anything else passes.** A
  port with no negative control cannot tell a fix from a coincidence. The probe is written against
  today's publication and has to reproduce the resolution failure first.
* **[B-07](docs/backlog/B-07-storage-conformance-corpus.md) comes before
  [B-09](docs/backlog/B-09-native-store-module.md), not after.** A corpus written after the second
  store describes the intersection of two implementations, including whatever both get wrong.
* **[B-11](docs/backlog/B-11-chronik-bridge-blocked.md) is blocked by a 404 in another repository.**
  `chronik-core-linuxx64` does not exist on Central; chronik's own native target is merged and
  unreleased. Merged is not released, and the release order follows from that rather than from
  preference.

## Stages

A stage is a field on the item, not a directory: documents cite items by id, so re-prioritising must
not move a file.

| Stage id | Stage | What closes it |
|---|---|---|
| `stage-0-gate` | The gate and the negative control | the documentation checks run in CI, and a native consumer project reproduces the resolution failure being fixed |
| `stage-1-portable` | The portable four | `linuxX64` on the modules that already compile anywhere, the end-to-end saga suites running there too, and the per-saga lock observed under the native memory model |
| `stage-2-ktor` | The HTTP surface | `petich-ktor` on both targets, the `-jvm` coordinates gone from the catalogue, the routing test running on native |
| `stage-3-storage` | Somewhere to store it | the conformance corpus first, the driver question answered, then the store that passes it — with the clock it is handed rather than read |
| `stage-4-bridge` | The bridge | `petich-chronik` on both targets, once chronik publishes a native variant |
| `stage-5-release` | Release | the guards see what is published, CI stops re-downloading the toolchain, the module table says which targets, and the release goes out in the order the dependency forces |

A stage closes as a whole and gets a line here: what came out beyond the plan, and which research
hypothesis was confirmed or refuted.

### Where the port stands — 2026-09-17

**Ten of sixteen items are done, and a Kotlin/Native consumer can take six of the eight published
modules.** `stage-0-gate` and `stage-2-ktor` are closed; `stage-1-portable` is closed except for one
suite that turned into a question; `stage-5-release` is closed except for the release itself.

What the stages produced beyond their plan:

* **The probe pays for itself twice.** [B-02](docs/backlog/B-02-native-consumer-probe.md) built it as
  a negative control; [B-12](docs/backlog/B-12-guards-meet-the-native-variants.md) made it a CI step,
  where it now also answers the question no build can — a module that declares a native target and
  publishes no native variant is named before a consumer is compiled.
* **Two premises in this file were wrong, and both were corrected where they were written.**
  [B-05](docs/backlog/B-05-concurrency-under-the-native-memory-model.md) asked for coverage that
  [B-03](docs/backlog/B-03-linux-target-on-the-portable-four.md) had already delivered unannounced;
  [B-13](docs/backlog/B-13-ci-and-the-konan-toolchain.md) carries a correction of a correction —
  a log line read as "the toolchain is already here" was about something else, and the gigabyte was
  being downloaded on every run after all.
* **The import list is not the JVM surface.** `getOrDefault`, `putIfAbsent`, `computeIfPresent` are
  `java.util.Map` methods available on a plain Kotlin `Map`, invisible above the file and unresolved
  on the second target ([B-04](docs/backlog/B-04-scenario-suites-on-both-targets.md),
  [B-06](docs/backlog/B-06-ktor-module-and-the-jvm-pinned-catalogue.md)). Declaring the target and
  reading the errors is the method; grepping imports is not.
* **The storage contracts got their first test of any kind**
  ([B-07](docs/backlog/B-07-storage-conformance-corpus.md)), and the corpus found something before a
  second store exists: a store with no version predicate breaks an *outbox* rule, because the refusal
  never happens and the events of the stale update are written.

**What is left is four items and two decisions, and none of them is work this backlog can do on its
own:**

| Item | Waiting for |
|---|---|
| [B-08](docs/backlog/B-08-which-database-for-the-native-store.md) `[?]` | the owner: Postgres through sqlx4k, or SQLite. The driver decides the module's name, its dialect and what the corpus runs against |
| [B-16](docs/backlog/B-16-access-scoring-decimal-fixture.md) `[?]` | the owner: what happens to a suite whose fixture does decimal finance |
| [B-09](docs/backlog/B-09-native-store-module.md), [B-10](docs/backlog/B-10-the-clock-the-second-store-cannot-read.md) | B-08 |
| [B-11](docs/backlog/B-11-chronik-bridge-blocked.md) | a chronik release carrying its native variants — [youndie/chronik#21](https://github.com/youndie/chronik/issues/21) |
| [B-15](docs/backlog/B-15-release-order-and-the-first-native-version.md) | B-11, and then a release, which is a decision rather than a task |

**The honest summary of the port so far:** a native service can take the engine, its HTTP surface and
the three independent modules, and still has nowhere to store a saga. That gap is B-09, and B-09
waits on a driver nobody has asked for yet (§1.9 of the research: there is no native consumer of
petich today). Stopping here is the correct place to stop.

## Labels

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX — generated by scripts/backlog_index.py; do not edit by hand -->

## Open (4)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-10](docs/backlog/B-10-the-clock-the-second-store-cannot-read.md) `[ ]` | The two wall-clock reads the second store cannot copy | P2 | S | B-09 |
| [B-11](docs/backlog/B-11-chronik-bridge-blocked.md) `[ ]` | petich-chronik stays JVM-only until chronik publishes a native variant | P2 | S | - |
| [B-15](docs/backlog/B-15-release-order-and-the-first-native-version.md) `[ ]` | The first release carrying native variants, and the order it has to go out in | P2 | S | B-12 |
| [B-16](docs/backlog/B-16-access-scoring-decimal-fixture.md) `[?]` | AccessScoring stays JVM-only because its fixture does decimal finance | P2 | M | - |

## Closed (12)

**The gate and the negative control**

- [B-01](docs/backlog/B-01-docs-gate.md) `[x]` - A documentation gate, so the port's decisions cannot rot unseen
- [B-02](docs/backlog/B-02-native-consumer-probe.md) `[x]` - A linuxX64 consumer project that must fail today

**The portable four**

- [B-03](docs/backlog/B-03-linux-target-on-the-portable-four.md) `[x]` - linuxX64 on the four modules that already compile anywhere
- [B-04](docs/backlog/B-04-scenario-suites-on-both-targets.md) `[x]` - The three end-to-end saga suites run on the JVM only
- [B-05](docs/backlog/B-05-concurrency-under-the-native-memory-model.md) `[x]` - The per-saga lock has never run under the Kotlin/Native memory model

**The HTTP surface**

- [B-06](docs/backlog/B-06-ktor-module-and-the-jvm-pinned-catalogue.md) `[x]` - petich-ktor: the catalogue names -jvm coordinates, which cannot resolve for a native target

**Somewhere to store it**

- [B-07](docs/backlog/B-07-storage-conformance-corpus.md) `[x]` - A conformance corpus for the four storage contracts, written while there is one implementation
- [B-08](docs/backlog/B-08-which-database-for-the-native-store.md) `[x]` - Which database does a native consumer store sagas in — Postgres through sqlx4k, or SQLite?
- [B-09](docs/backlog/B-09-native-store-module.md) `[x]` - The native store: the four contracts implemented over sqlx4k, driver supplied by the application

**Release**

- [B-12](docs/backlog/B-12-guards-meet-the-native-variants.md) `[x]` - After the port, half of what is published is checked by nobody
- [B-13](docs/backlog/B-13-ci-and-the-konan-toolchain.md) `[x]` - CI downloads the Kotlin/Native toolchain on every run
- [B-14](docs/backlog/B-14-module-table-says-which-targets.md) `[x]` - The README module table says nothing about targets, and after the port the answer differs per module

<!-- END INDEX -->
