---
id: B-26
title: "findStuck hands out sagas through a second queue that a consumer's claim does not cover"
status: done
priority: P1
size: M
stage: stage-8-upgrade
---

# B-26 — one arbitrated queue, one not

[B-19](B-19-nobody-picks-up-a-saga-that-died-mid-pass.md) added `findStuck` and said what it does not
do: there is no lease, two sweepers can both re-drive one saga, and "a lease is the item to open when
someone runs more sweepers than engines". Someone already does.

**konekt built it, before this existed and for money.** Its `ClaimedSweep` decorates
`ExpiringPetichRepository` and arbitrates in the database — one row, one conditional write, a lease
so a sweeper that dies mid-compensation does not hold the saga for ever. It exists because konekt's
own B-64 found a purchase being refunded **once per running replica**, and the migration that carries
the claim table says so in full. The decorator wraps `findExpired`, which until 0.3.0 was the only
call that handed a replica work.

0.3.0 adds a second such call, and the decorator does not cover it. A consumer who solved this
problem carefully now has it back, in a place their solution does not look — and nothing in the type
system, the documentation or the corpus says so.

**And the second queue is worse than the first in one specific way.** `findExpired` hands out sagas
to be rolled back; `findStuck` hands them out to be carried FORWARD, which calls `intercept()`. The
contract from [B-18](B-18-the-failed-step-compensates-nothing.md) requires that to be idempotent —
but idempotent is not free. A charge at a payment provider carrying an idempotency key survives a
second call; one without a key does not, and that is the same money konekt's B-64 was about.

**The question is for the owner, because the three answers differ in what petich owns:**

- **Say it and nothing more.** The KDoc of `findStuck` and the sweeper's documentation state that a
  consumer arbitrating one queue must arbitrate both, and name `ClaimedSweep` as the known shape.
  Cheapest, and it is a sentence a consumer has to remember on every upgrade.
- **Make both queues pass through one seam** — an optional `ClaimingPetichRepository` the sweeper
  consults before handing any saga to an engine, whichever query produced it. In the grain of
  `OutboxAwarePetichRepository` and `SideEffectAwarePetichRepository`: optional, visible in the type,
  and a store that cannot do it says so at construction rather than at runtime. It also turns
  konekt's decorator from two overrides into one implementation.
- **Ship the claim.** A table, a lease and a query — which means petich owns DDL, and that is the
  decision this library has refused twice and should not reverse for this. [B-24](B-24-a-release-that-adds-a-column-names-it-nowhere.md)
  has just built a guard resting on that refusal, so it is now load-bearing rather than a preference.
- **Make the queue ONE.** Not two methods but `findSweepable(now, limit)`, returning the expired and
  the stranded together; what to do with each — roll back or carry on — the sweeper reads off the
  status it already has. This is the option that was missing when the item was written, and it is
  **smaller** than the others: no new interface, one method FEWER on the repository, and a consumer's
  decorator covers both queues without knowing there are two. Arbitration stops being a thing that
  can be forgotten, because there is nothing to forget. What it costs is that two predicates with two
  different index paths become one call, which each store implements as a `UNION` or as two queries
  in a row — and it changes `findExpired`'s signature.

**The timing decides more than the merits.** `findStuck` is not released: it has no consumers, so
changing its shape today costs nothing. Once 0.3.0 is on Central, the fourth option is a breaking
change with a migration for everyone who implemented the interface. If it is that one, it is now or
not at all.

Recommended from here: the fourth, and failing that the second. The first is worth taking only as a
holding measure before the release, and the third not at all. The decision is still the owner's —
the fourth and second both put a shape into a repository contract that is deliberately small, and
that is not a call this loop makes.

- AC: a consumer that arbitrates the expiry queue cannot silently leave the stuck queue
  unarbitrated — by a type that makes both explicit, or by documentation a person has to read once;
  whichever is chosen, konekt's case is the test of it.
- Anchors: `petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt`,
  `petich-core/src/commonMain/kotlin/Petich.kt`

## Decided 2026-09-19 — the fifth option, and it was checked before it was chosen

**None of the four answered the question before the question.** All of them asked how to cover the
second queue with an EXTERNAL, OPTIONAL arbiter; the owner asked why the arbiter is external and
optional at all. A new consumer with two replicas and no decorator gets a doubled `intercept()` in
silence — and two replicas is an ordinary deployment, so an unarbitrated sweep is correct only on a
single instance. Exclusivity belongs in the contract rather than in a seam. Under that reading the
four options solve konekt's problem and leave petich's.

**(e): the saga's own row is the arbiter.** The optimistic lock already there does the arbitrating,
and no lease table is needed:

* **Stranded sagas.** `findStuck` selects on "not written for longer than N". A CAS `update` that
  bumps the version — and therefore `updated_at`, which the store stamps itself — before
  `intercept()` is the claim. The loser's version is stale and it fails the CAS before any effect. A
  replica reading after the claim no longer matches the predicate. The lease IS `stuckAfter`; there
  is no second parameter and no DDL, because the column `findStuck` needs exists either way.
* **Expired sagas.** No lease at all: the `PENDING_SIGNATURE → COMPENSATING` transition under CAS,
  before the first `compensate()`, is the claim. If the winner dies mid-rollback the saga becomes an
  ordinary stranded one and the other queue picks it up — two mechanisms collapse into one.

**The whole construction rests on one rule: a sweeper that loses the CAS SKIPS the saga rather than
retrying it.**

### What the three checks found

1. **The store stamps `updated_at` itself** on insert and on update, from the clock it was given
   (`ExposedPetichRepository.kt:75`, `:108`; `PostgresPetichStore.kt:185`). It is not in `Petich` at
   all, so a caller cannot forge it. Better than the option needed.
2. **A CAS touch needs no new API**: `repository.update(row.copy(version = row.version + 1))` is one,
   with the version predicate in both stores and `false` for the loser.
3. **`COMPENSATING` is written before the first `compensate()` — and does not arbitrate.** The place
   is right (`Petich.kt:698`, ahead of the rollback loop); the mechanism is not. It goes through
   `forceUpdateStateWithRetry` (`:926`), which re-reads, writes `latest.version + 1`, **never compares
   the status it read**, and repeats up to a hundred times. A second replica arriving after the winner
   reads `COMPENSATING` at v+1 and writes `COMPENSATING` at v+2 successfully, then compensates
   alongside it. That is not a CAS that arbitrates but one that wins at any cost, deliberately: the
   comment at `:694` says losing the mark would leave the engine compensating a saga the database
   shows as executing.

### The B-64 hypothesis: supported, and a different function is responsible

Not `processWithRetry` but `forceUpdateStateWithRetry`, which is the shorter path — though
`processWithRetry` (`:912`) is a second route to the same place and becomes the main one for the
stuck queue, since its retry re-runs `intercept()`.

**But the window is narrower than "no arbitration at all".** `expireSuspended` re-reads under its own
lock (`:854`) and answers `NotSuspended` for anything that is no longer `PENDING_SIGNATURE` (`:857`),
so a replica that arrives after the winner's write is turned away correctly. The race is only "both
read before either wrote" — narrow per saga, and routinely reached on a batch: two replicas polling
every 30 seconds take 50 rows each. **So `ClaimedSweep` closed a real window rather than treating a
symptom.** It cannot simply be deleted — but under (e) it collapses to nothing rather than to one
implementation.

### What (e) needs that does not exist

**The claim must sit OUTSIDE the retrying layers** — in the sweeper, before `process()` and before
`expireSuspended`. Then the engine is not touched at all: the loser skips, the winner goes in, and
the retries inside keep doing what they are for, which is competing with a live handler rather than
with a second sweeper.

For the expiry queue that means the sweeper makes the `PENDING_SIGNATURE → COMPENSATING` transition
itself, with a plain non-retrying `update`, and hands the already-claimed saga to the engine. The
argument at `:694` does **not** apply to that case: it is about losing the mark to an unknown writer,
and losing it to a second sweeper means the saga is being rolled back anyway. The engine cannot tell
those two losers apart today, which is exactly why the claim belongs where it can.

### The batch trap dissolves

In (d) and in `ClaimedSweep` the lease on the last saga of a batch drains while the first is being
worked. In (e) the claim is taken one row at a time immediately before that row is processed, and the
lease equals `stuckAfter` — the trap has nowhere to live. After "no DDL and no new interfaces", this
is the strongest argument for (e).

### The corpus needs nothing new, and that is the point

Exclusivity rests on `update`'s version predicate, which the corpus already holds every store to
("an update carrying a stale version is refused and changes nothing"). Concurrency is explicitly out
of the corpus's scope — it runs one caller at a time — and the existing home for it is
`ConcurrentWritersTest` ("four writers racing to advance one saga leave exactly one winner"). What
this item owes is a test that the SWEEPER's loser skips rather than retries, not a new storage rule.

### Still open after (e)

Arbitration between sweepers does not arbitrate a sweeper against a live, slow handler that holds no
lease. (e) weakens it — an ordinary pass writes the row at every step boundary, which is a heartbeat
with one-step granularity — but `stuckAfter > max step time` stays a requirement. It is in the
parameter's KDoc as a formula already; the heartbeat effect is not, and should be.

- AC (superseding the one above): a second sweeper that loses the claim skips the saga and says so,
  rather than retrying it or working it in parallel; the stuck queue's claim moves the row out of its
  own predicate; the expiry queue's claim is the status transition, taken once; no new public
  interface, no new column, no lease table; `ClaimedSweep`'s case is the test of it.

## Closed 2026-09-19

(e), as decided. One write per saga, taken before anything touches it, and the row's own optimistic
lock decides. No lease table, no new interface, no column, and `ExpireResult.Contended` plus
`onContended` so the loser is countable rather than silent.

* **Stranded queue** — `update(row.copy(version = version + 1))` in the sweeper. The store re-stamps
  `updated_at` itself, so the row stops matching the predicate that found it; the lease is
  `stuckAfter` and there is no second parameter. A replica holding the row from before is refused
  **before** it calls `intercept()`.
* **Expiry queue** — the `PENDING_SIGNATURE → COMPENSATING` transition, written with a plain `update`
  whose `false` is obeyed.

### Three compromises, named rather than swept

**1. This touched the engine, against the instruction the iteration started with.** The claim for
expired sagas is inside `expireSuspended` (`expireClaimed`), not in the sweeper. Both alternatives
were worse:

* a version-bump claim in the sweeper leaves the window open, because the write that follows goes
  through `forceUpdateStateWithRetry`, and that one re-reads and writes again until it wins — two
  replicas both succeed and both roll back;
* moving the transition into the sweeper closes it, and loses `ExpireResult`'s whole vocabulary: the
  expiry reason that reaches `onCompensation`, and the chain-mismatch report from
  [B-21](B-21-the-chain-is-addressed-by-position.md), both of which live on that path. It would also
  put the engine's `compensatingFromIndex` arithmetic in the sweeper.

What the instruction was protecting — the retry semantics `Petich.kt:694` defends — is untouched.
Those retries compete with a live handler, which is what they are for; the arbitration now happens
before them.

**2. An expiry costs one extra write.** The rollback's own first write records `COMPENSATING` a
second time, because `triggerCompensation` was left alone. That is the price of the loser stopping
before a single `compensate()`, and it is paid only on that path. `WriteCountTest`'s scenario does
not include an expiry, so the number it holds is unchanged.

**3. The gap the decision already named is still there.** A sweeper is arbitrated against another
sweeper, not against a live, slow handler holding no claim. `stuckAfter > max step time` remains the
condition, and the README now says why in those words.

### The corpus needed nothing, which was the point

Exclusivity rests on `update`'s version predicate — the rule the corpus already holds every store to
("an update carrying a stale version is refused and changes nothing") — and the concurrent case is
`ConcurrentWritersTest`'s ("four writers racing to advance one saga leave exactly one winner"),
which runs against a real Postgres on `jvm` and `linuxX64`. **The guarantee this item rests on was
already proved in both stores before the item was written.** That is the difference between (e) and
the four options it replaced, all of which would have needed a new contract and a new rule.

### Verified

Four cases, on both targets: a loser on each queue, a winner on each. The losing ones assert the
absence of work — no `compensate()`, no `intercept()`, and the row untouched — which is the rule
rather than the mechanism. A lost race is reproduced by refusing one write rather than by two
threads and a hope; a flaky test pretending to be a concurrency test would be worse than none.

Two mutations after the change was committed: ignoring the refused claim on the expiry path fails
the expiry loser case, and dropping the claim from the stuck sweep fails the stranded loser case.
Neither touches the other's test. 321 tests, 0 failures, `./gradlew build` on the Linux box.

### What konekt can now delete

`ClaimedSweep`, its `saga_sweep_claim` table and the `V12` migration become redundant: petich
arbitrates both queues itself, and a claim on top of a claim only narrows a window that is already
closed. Reported rather than done — youndie/konekt#48 is the open thread there, and that repository
is not this loop's to edit.

