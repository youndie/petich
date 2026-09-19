---
id: B-26
title: "findStuck hands out sagas through a second queue that a consumer's claim does not cover"
status: question
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
