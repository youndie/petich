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

**Thirteen of sixteen items are done, and a Kotlin/Native service can now run petich end to end.**
`stage-0-gate`, `stage-2-ktor` and `stage-3-storage` are closed; `stage-1-portable` is closed except
for one suite that became a question; `stage-5-release` is closed except for the release itself.

The sentence this summary carried a few hours ago — *a native service can take the engine and still
has nowhere to store a saga* — is no longer true. `petich-sqlx4k-postgres`
([B-09](docs/backlog/B-09-native-store-module.md)) implements the four storage contracts over
sqlx4k, takes the driver from the application, and is accepted by the corpus on both targets. The
acceptance was a native binary with its own driver running a saga through create → suspend → resume
→ complete against a real Postgres, with its outbox row.

What the stages produced beyond their plan:

* **The probe pays for itself three times.** A negative control in
  [B-02](docs/backlog/B-02-native-consumer-probe.md); a CI guard in
  [B-12](docs/backlog/B-12-guards-meet-the-native-variants.md) that also catches "declared a native
  target, published no native variant"; and in B-09 the thing that runs a whole saga — where the
  fact that it **links** is the evidence that the store carries no driver.
* **The corpus earned its place before the second store existed.** On its first run against the new
  store it reported five broken rules and named one cause (a parameter the UPDATE never mentions).
  Written after that store, it would have described the intersection of the two instead.
* **Three premises in this file were wrong, and each correction sits where it was written.**
  [B-05](docs/backlog/B-05-concurrency-under-the-native-memory-model.md) asked for coverage
  [B-03](docs/backlog/B-03-linux-target-on-the-portable-four.md) had already delivered;
  [B-13](docs/backlog/B-13-ci-and-the-konan-toolchain.md) holds a correction of a correction; and
  [B-04](docs/backlog/B-04-scenario-suites-on-both-targets.md)'s list of JVM-only usages was drawn
  from imports, which do not show `java.util.Map`'s methods on a Kotlin `Map`.
* **Two findings came from running rather than reading:** the engine does not re-run the step that
  suspended, and a consumer that stores sagas needs the serialization plugin — both found by the
  probe failing, both now in the documents.

**What is left is three items, and none of them is work this backlog can do on its own:**

| Item | Waiting for |
|---|---|
| [B-11](docs/backlog/B-11-chronik-bridge-blocked.md) | a chronik release carrying its native variants — [youndie/chronik#21](https://github.com/youndie/chronik/issues/21); `chronik-core-linuxx64` is still 404 |
| [B-15](docs/backlog/B-15-release-order-and-the-first-native-version.md) | B-11, and then a release, which is a decision rather than a task |
| [B-16](docs/backlog/B-16-access-scoring-decimal-fixture.md) `[?]` | the owner: what happens to a suite whose fixture does decimal finance |

## Labels

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX — generated by scripts/backlog_index.py; do not edit by hand -->

## Open (2)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-15](docs/backlog/B-15-release-order-and-the-first-native-version.md) `[ ]` | The first release carrying native variants, and the order it has to go out in | P2 | S | B-12 |
| [B-16](docs/backlog/B-16-access-scoring-decimal-fixture.md) `[?]` | AccessScoring stays JVM-only because its fixture does decimal finance | P2 | M | - |

## Closed (14)

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
- [B-10](docs/backlog/B-10-the-clock-the-second-store-cannot-read.md) `[x]` - The two wall-clock reads the second store cannot copy

**The bridge**

- [B-11](docs/backlog/B-11-chronik-bridge-blocked.md) `[x]` - petich-chronik stays JVM-only until chronik publishes a native variant

**Release**

- [B-12](docs/backlog/B-12-guards-meet-the-native-variants.md) `[x]` - After the port, half of what is published is checked by nobody
- [B-13](docs/backlog/B-13-ci-and-the-konan-toolchain.md) `[x]` - CI downloads the Kotlin/Native toolchain on every run
- [B-14](docs/backlog/B-14-module-table-says-which-targets.md) `[x]` - The README module table says nothing about targets, and after the port the answer differs per module

<!-- END INDEX -->
