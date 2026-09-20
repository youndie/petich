# petich — backlog

> Document role: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. This page holds the generated index and
> everything that is not an item: the goal, the stages, the decisions.
>
> A new item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.

## The goal

Two lines of work have lived in this file. **The Kotlin/Native port**, stages 0–5, is done and its
account is kept below because the decisions outlive the code. **What the engine promises when a
process dies**, stages 6–7, was opened afterwards and is where the open items are.

## The port — the goal it had

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
| `stage-6-recovery` | What the engine undoes | a failed step is accounted for, a saga abandoned by a dead process is picked up, a refusal cannot silently keep what happened, and a resume cannot land on a different step |
| `stage-7-write-cost` | What the writes cost | the 11 updates per saga stop paying for what does not change |
| `stage-8-upgrade` | What taking 0.3.0 costs a consumer | the schema changes are named, the statements say what they lock, and the second queue is arbitrated like the first |
| `stage-9-definition` | A saga is a definition, not a filtered list | the order of one saga is readable in one place, a check cannot roll anything back, what a step did belongs to that step, and the interceptor model is gone |

A stage closes as a whole and gets a line here: what came out beyond the plan, and which research
hypothesis was confirmed or refuted.

### The port is done — 2026-09-17

**`io.github.youndie.petich` 0.2.0 is on Maven Central with native variants**, fifteen items are
done and one is dropped ([B-16](docs/backlog/B-16-access-scoring-decimal-fixture.md): the suite whose
fixture does decimal finance stays on the JVM — the native target loses that arithmetic, not any
part of petich).

**One item was opened by the release week and closed the same day.**
[B-17](docs/backlog/B-17-native-store-test-flake.md): the native store's four-writer test failed once
on a hosted runner with an I/O error from the driver. It had been seen before and written off as an
oddity of one container — the wrong call, and B-09 says so where it was made. The mechanism turned
out to be the harness rather than the driver: readiness was asked with `docker exec pg_isready`,
which answers 0.7–0.9 s before Postgres answers on the published port, and a TCP connect to that
port accepts 8 times out of 8 while nothing is listening behind it. Reproduced deliberately, fixed
by asking the protocol from the host, and closed with no retry, no longer timeout and no test moved
to jvm-only.

A Kotlin/Native service takes the engine, its HTTP surface, the three independent modules, the
conformance corpus and a **store** — eight coordinates, all resolved from Central by a native
consumer that then ran a saga end to end against a real Postgres. `petich-postgres` stays JVM-only
by decision, writing the same columns as its native counterpart, so a system moves one process at a
time.

| Stage | Closed by |
|---|---|
| `stage-0-gate` | the documentation gate, and a probe that had to fail before anything could pass |
| `stage-1-portable` | `linuxX64` on the four independent modules; two of the three saga suites moved to both targets; the per-saga lock observed under a real dispatcher on both |
| `stage-2-ktor` | `petich-ktor` on both targets, the `-jvm` coordinates gone from the catalogue |
| `stage-3-storage` | the corpus first, the driver question answered by the owner, then `petich-sqlx4k-postgres` — with the clock a parameter in all three stores |
| `stage-4-bridge` | `petich-chronik`, after chronik released 0.2.0 |
| `stage-5-release` | the guards that see native variants, the cached toolchain, the module table, and the release itself |

**What this backlog is worth keeping for, beyond the code:**

* **Everything was accepted by a consumer, never by a build log.** The probe from B-02 reported
  REFUSED before the port and RESOLVED after it, against the local publication, then against
  reposilite, then against Central — the same code answering the same question at three distances.
* **The corpus preceded the second store and paid for it immediately**: five rules broken, one cause
  named, before anything was published.
* **Four premises written in this file turned out wrong** — B-05's, B-13's twice, B-04's list of
  JVM-only usages — and each correction sits where the claim was, not in a commit message.
* **The release was rehearsed on a snapshot** and only then uploaded, because a version on Central
  cannot be rewritten or taken back. The rehearsal caught nothing; it cost one afternoon and would
  have caught everything.
* **The last step stayed a person's.** The upload workflow leaves the bundle staged by design, and
  the Publish click for chronik and for petich was the owner's.

## After the port: what the README promises and the engine does not

Five items opened on 2026-09-19 out of a review of the README by a reader who had not seen the code,
and then checked against it. Two of the five reviewer's claims were wrong about the mechanism and
right about the consequence; the corrected versions are in the items. The line they share is that
the public text promises more than the engine delivers — "undoes exactly what had already happened",
and 17 writes per saga bought so that a dead process "never leaves a saga in an unknown position".

Three facts the review turned up, all verified in the code and none of them visible from the outside:

* **Rollback starts at N−1** ([B-18](docs/backlog/B-18-the-failed-step-compensates-nothing.md)), so
  the ordinary ambiguous failure — the call landed, the answer did not — leaks the effect of the
  step that failed. Decided first, because it fixes what `compensate()` is allowed to assume, and
  [B-19](docs/backlog/B-19-nobody-picks-up-a-saga-that-died-mid-pass.md) makes its second path more
  frequent.
* **The recovery is paid for and not wired up**
  ([B-19](docs/backlog/B-19-nobody-picks-up-a-saga-that-died-mid-pass.md)). The engine resumes an
  interrupted pass and an interrupted rollback correctly; nothing in this library ever calls it for
  a saga left in `PROCESSING` or `COMPENSATING`, and a `compensate()` that keeps throwing has no
  terminal state at all.
* **Two results refuse a saga and only one rolls it back**
  ([B-20](docs/backlog/B-20-reject-after-an-effect-keeps-it.md)), with the distinction written down
  nowhere. Every `Reject` in the test suites happens to sit before `EXECUTION`, so the suite is
  green and silent about it.

The README was corrected first, in the same change that opened these — an hour of text against a
public page that overstates the guarantee, rather than waiting for the engine to catch up with it.

## What the 0.3.0 rehearsal found — 2026-09-19

`0.3.0.56` was put in front of both real consumers before anything irreversible: konekt and shashki
built and tested against the snapshot, with their version catalogues temporarily repointed and every
edit reverted afterwards. Both are on **0.1.0**, so the rehearsal crossed two releases.

**Both hit the same two walls, in the same order**, and both were green past them — konekt 590 tests,
shashki 468 with nothing failing. The walls are the items above: an exhaustive `when` over
`PetichStatus`, one site each, and three columns that do not exist until somebody writes a migration.

What the rehearsal caught that reading the diff did not:

* **konekt had already modelled `COMPENSATION_FAILED`** inside its own `COMPENSATING`, with a comment
  calling it "the one state that needs a person". The engine can finally say it; both consumers'
  first instinct — and the rehearsal's patch — is to fold it back into the bucket they have.
* **A consumer's guard found [B-25](docs/backlog/B-25-the-tuning-statement-takes-a-lock-it-does-not-mention.md)**,
  not ours: konekt refuses a migration that does not bound its lock wait, and the fill-factor
  statement does not mention that it takes one.
* **[B-26](docs/backlog/B-26-the-second-queue-has-no-arbitration.md) has a precedent in the wild.**
  konekt built a claim-and-lease decorator for the expiry queue after a rollback refunded once per
  replica; `findStuck` is a second queue it does not cover.
* **A green rehearsal is not a clean bill.** konekt's top-up suite passes because no test makes the
  payment gateway *throw* — it covers the declined path, which B-18 did not change. The hole B-18
  opened in `TopUpInterceptors.kt:82` is real and invisible from here — reported as
  youndie/konekt#48 and youndie/shashki#13, each with a fix shape taken from that repository's own
  records — the ledger entry in one, the charge id in the other. **Both consumers were green**: the
  paths are the ones their suites do not exercise.

The first of these is why 0.3.0 does not go to Central yet:
[B-24](docs/backlog/B-24-a-release-that-adds-a-column-names-it-nowhere.md) is a release blocker, and
the fix is a paragraph and a guard.

## The definition model — opened 2026-09-19

Stages 6 and 7 fixed five defects in the interceptor model and left the model. This stage replaces
it: a saga becomes a value that spells its own order, a step loses everything that is not the step,
and the two roles one interface serves today become two types.

The argument is in [research-petich-dsl.md](docs/research/research-petich-dsl.md), and it rests on
counted facts rather than taste: **12 of the 27 compensations in the two consumers are empty**,
written to satisfy a type; every `Reject` sits before the first effect because the author could not
be asked to know otherwise; and a step's own output reaches its compensation through a map shared by
every step, which is the mechanism behind youndie/shashki#13.

**Three of the five items just closed stop being guards and become impossible states.** B-21's
fingerprint compares names instead of positions, B-20's rollback-on-refusal becomes a type that has
no `compensate`, and B-18's contract gets the evidence it needs in the engine rather than in each
implementation's head.

Two things worth knowing before reading the items:

* **The consumers are ours.** konekt and shashki exist to exercise this portfolio, so
  [B-32](docs/backlog/B-32-migrate-the-two-consumers.md) is the acceptance rather than the migration
  cost — and it is why [B-33](docs/backlog/B-33-remove-the-interceptor-model.md) removes the old
  model outright instead of leaving an adapter that would keep the discipline it exists to retire.
* **[B-27](docs/backlog/B-27-release-0-3-0-before-the-redesign.md) was opened as a blocker and
  refused the same day**, for a better reason than it was opened with: Central cannot be rewritten
  or taken back, so publishing 0.3.0 would pin the interceptor model there one release before
  [B-33](docs/backlog/B-33-remove-the-interceptor-model.md) deletes it. The five fixes of stages 6
  and 7 therefore reach Central only with the new model, and until then the published petich is one
  in which a failed step is never compensated. Acceptable because the audience is a shop window: the
  only two consumers are ours and resolve snapshots.

## Labels

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX — generated by scripts/backlog_index.py; do not edit by hand -->

## Open (7)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-51](docs/backlog/B-51-failed-without-being-undone.md) `[~]` | A storage error after an effect writes FAILED and undoes nothing | P0 | M | - |
| [B-52](docs/backlog/B-52-foreign-code-inside-the-catch.md) `[ ]` | A hung announcement rolls the saga back, and a handler that throws decides its fate | P1 | M | - |
| [B-53](docs/backlog/B-53-resuspend-remembers-neither-half.md) `[ ]` | A parked cascade is not undone, and its phase is not remembered | P1 | M | - |
| [B-54](docs/backlog/B-54-what-a-rollback-will-end-as.md) `[ ]` | A resumed rollback forgets it was a refusal, and can drag a terminal saga back | P1 | S | - |
| [B-55](docs/backlog/B-55-the-sweeper-counts-a-refusal-as-a-rescue.md) `[ ]` | The sweeper counts a refusal as a rescue, and one failure blocks both queues | P2 | S | - |
| [B-57](docs/backlog/B-57-the-constructor-tail-and-two-leaks.md) `[ ]` | The engine's constructor grows a tail, and a reason string reaches the outbox | P2 | S | - |
| [B-56](docs/backlog/B-56-d14-is-wrong-about-the-stuck-queue.md) `[ ]` | D14 says nothing is written, and on the stuck queue something is | P3 | S | - |

## Closed (50)

**The gate and the negative control**

- [B-01](docs/backlog/B-01-docs-gate.md) `[x]` - A documentation gate, so the port's decisions cannot rot unseen
- [B-02](docs/backlog/B-02-native-consumer-probe.md) `[x]` - A linuxX64 consumer project that must fail today

**The portable four**

- [B-03](docs/backlog/B-03-linux-target-on-the-portable-four.md) `[x]` - linuxX64 on the four modules that already compile anywhere
- [B-04](docs/backlog/B-04-scenario-suites-on-both-targets.md) `[x]` - The three end-to-end saga suites run on the JVM only
- [B-05](docs/backlog/B-05-concurrency-under-the-native-memory-model.md) `[x]` - The per-saga lock has never run under the Kotlin/Native memory model
- [B-16](docs/backlog/B-16-access-scoring-decimal-fixture.md) `[-]` - AccessScoring stays JVM-only because its fixture does decimal finance

**The HTTP surface**

- [B-06](docs/backlog/B-06-ktor-module-and-the-jvm-pinned-catalogue.md) `[x]` - petich-ktor: the catalogue names -jvm coordinates, which cannot resolve for a native target

**Somewhere to store it**

- [B-07](docs/backlog/B-07-storage-conformance-corpus.md) `[x]` - A conformance corpus for the four storage contracts, written while there is one implementation
- [B-08](docs/backlog/B-08-which-database-for-the-native-store.md) `[x]` - Which database does a native consumer store sagas in — Postgres through sqlx4k, or SQLite?
- [B-09](docs/backlog/B-09-native-store-module.md) `[x]` - The native store: the four contracts implemented over sqlx4k, driver supplied by the application
- [B-10](docs/backlog/B-10-the-clock-the-second-store-cannot-read.md) `[x]` - The two wall-clock reads the second store cannot copy
- [B-17](docs/backlog/B-17-native-store-test-flake.md) `[x]` - The native store's concurrency test fails sometimes, with an I/O error from the driver

**The bridge**

- [B-11](docs/backlog/B-11-chronik-bridge-blocked.md) `[x]` - petich-chronik stays JVM-only until chronik publishes a native variant

**Release**

- [B-12](docs/backlog/B-12-guards-meet-the-native-variants.md) `[x]` - After the port, half of what is published is checked by nobody
- [B-13](docs/backlog/B-13-ci-and-the-konan-toolchain.md) `[x]` - CI downloads the Kotlin/Native toolchain on every run
- [B-14](docs/backlog/B-14-module-table-says-which-targets.md) `[x]` - The README module table says nothing about targets, and after the port the answer differs per module
- [B-15](docs/backlog/B-15-release-order-and-the-first-native-version.md) `[x]` - The first release carrying native variants, and the order it has to go out in

**What the engine undoes**

- [B-18](docs/backlog/B-18-the-failed-step-compensates-nothing.md) `[x]` - The step that failed is never compensated, and the ambiguous failure is the common case
- [B-19](docs/backlog/B-19-nobody-picks-up-a-saga-that-died-mid-pass.md) `[x]` - Nothing picks up a saga left in PROCESSING or COMPENSATING, and a failing compensation has no terminal state
- [B-20](docs/backlog/B-20-reject-after-an-effect-keeps-it.md) `[x]` - Reject after a step has touched the outside world keeps what that step did
- [B-21](docs/backlog/B-21-the-chain-is-addressed-by-position.md) `[x]` - A saga's position is an index into a chain assembled at runtime, and nothing notices when the chain changes

**What the writes cost**

- [B-22](docs/backlog/B-22-eleven-updates-rewrite-a-column-that-never-changes.md) `[x]` - Every saga UPDATE rewrites the immutable payload column, and the tables are created with no room for HOT
- [B-23](docs/backlog/B-23-nothing-checks-the-write-count.md) `[x]` - The write count the README sells the engine on is measured by nothing

**What taking 0.3.0 costs a consumer**

- [B-24](docs/backlog/B-24-a-release-that-adds-a-column-names-it-nowhere.md) `[x]` - A release that adds a column to the saga table names it nowhere, and the consumer learns it at runtime
- [B-25](docs/backlog/B-25-the-tuning-statement-takes-a-lock-it-does-not-mention.md) `[x]` - The fill-factor statement takes an ACCESS EXCLUSIVE lock on the busiest table and does not say so
- [B-26](docs/backlog/B-26-the-second-queue-has-no-arbitration.md) `[x]` - findStuck hands out sagas through a second queue that a consumer's claim does not cover

**A saga is a definition, not a filtered list**

- [B-27](docs/backlog/B-27-release-0-3-0-before-the-redesign.md) `[-]` - 0.3.0 goes to Central before the redesign starts
- [B-28](docs/backlog/B-28-the-types-and-the-builder.md) `[x]` - PetichStep, PetichCheck and a definition that says the order out loud
- [B-29](docs/backlog/B-29-what-a-step-did-belongs-to-that-step.md) `[x]` - What a step did is recorded beside its key, not in the payload everyone shares
- [B-30](docs/backlog/B-30-globals-inline-in-the-chain.md) `[x]` - A cross-cutting check must be visible where the saga is read
- [B-31](docs/backlog/B-31-the-engine-knows-which-definition-owns-a-saga.md) `[x]` - engineFor and onUnowned exist because no value says what an order saga is
- [B-32](docs/backlog/B-32-migrate-the-two-consumers.md) `[x]` - Rewrite konekt's and shashki's sagas in the new model — the acceptance
- [B-33](docs/backlog/B-33-remove-the-interceptor-model.md) `[x]` - Remove PetichInterceptor, and do not leave an adapter behind
- [B-34](docs/backlog/B-34-the-two-stores-type-a-column-differently.md) `[x]` - The two stores type the same column differently, and the audit cannot see it
- [B-35](docs/backlog/B-35-an-emit-before-a-non-proceed-outcome-vanishes.md) `[x]` - A member that emits and then suspends loses the announcement, silently
- [B-36](docs/backlog/B-36-a-member-cannot-name-itself.md) `[x]` - A member cannot name itself, and observability was hanging off the name
- [B-37](docs/backlog/B-37-a-member-can-ask-again.md) `[x]` - A member had no way to wait for another answer at itself
- [B-38](docs/backlog/B-38-ship-a-test-double-for-the-member-context.md) `[x]` - Both consumers wrote the same context double, and it broke twice

**stage-10-review**

- [B-39](docs/backlog/B-39-a-phase-that-admits-an-acting-member.md) `[x]` - AUTHORIZATION admits an acting member, so the phase stops meaning anything
- [B-40](docs/backlog/B-40-declaration-order-is-not-run-order.md) `[x]` - A definition can read in one order and run in another
- [B-41](docs/backlog/B-41-what-a-failed-announcement-means.md) `[x]` - announce takes a full step, and its failure has no defensible meaning
- [B-42](docs/backlog/B-42-the-builder-shares-a-name-with-the-instance.md) `[x]` - petichDefinition<T>() returns a definition and Petich is an instance
- [B-43](docs/backlog/B-43-absence-of-a-record-is-not-evidence-of-absence.md) `[x]` - The guard the README recommends is blind in the case it exists for
- [B-44](docs/backlog/B-44-a-refused-chain-has-no-way-out.md) `[x]` - A saga whose chain changed is refused for ever and has no status for it
- [B-45](docs/backlog/B-45-the-readme-describes-the-model-before-the-last-four-items.md) `[x]` - The README describes the model as it was before stage-10
- [B-46](docs/backlog/B-46-nothing-compiles-the-readme.md) `[x]` - Nothing compiles the README, so its examples rot silently
- [B-47](docs/backlog/B-47-a-key-per-member-meets-a-member-that-acts-twice.md) `[x]` - The idempotency rule and resuspendFor contradict each other
- [B-48](docs/backlog/B-48-the-far-side-may-not-cancel-by-your-name.md) `[x]` - The rule assumes the far side can cancel by the caller's name, and often it cannot
- [B-49](docs/backlog/B-49-a-failed-announcement-never-leaves-the-database.md) `[x]` - A failed announcement leaves a counter and nothing else
- [B-50](docs/backlog/B-50-an-announcement-can-run-twice.md) `[x]` - An announcement is re-run after a crash and nothing says it must tolerate that

<!-- END INDEX -->
