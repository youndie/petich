---
id: B-10
title: "The two wall-clock reads the second store cannot copy"
status: done
priority: P2
size: S
stage: stage-3-storage
blocked_by: [B-09]
---

# B-10 — `System.currentTimeMillis()`, twice, in the store

The JVM store stamps two columns from the platform clock: `outbox_events.created_at`
(`ExposedPetichRepository.kt:109`) and `idempotency_keys.created_at`
(`ExposedIdempotencyRepository.kt:45`). Both are suppressed against the `ktlint:kapkan:wall-clock`
rule with a reason, and the reason is already a known defect — replica skew reorders the outbox
queue, tracked as youndie/petich#20, whose fix is a server-side column default and therefore a
change to every consumer's DDL.

- **A second store forces the question the first one deferred.** Whatever it does here becomes a
  second precedent: another platform read, or a clock the store is handed. The engine already takes
  its clock as a parameter (`PetichClock`), which is the form to copy — the store should take one
  too, and the default implementation is the only place a platform call appears.
- **Rejected: fixing #20 as part of this.** The fix is a column default in DDL this library does not
  ship, so it lands in every consumer's migrations — a release decision, not a line in a port. What
  this item does is stop the port from adding a third instance of the same read.
- **Does not cover:** the ordering guarantee itself. Delivery order is not promised; what skew
  degrades is fairness, and that stays #20's subject.

- AC: the native store takes a clock and reads no platform clock of its own; the Exposed store's two
  reads are either given the same shape or explicitly left with the reason written next to them, so
  the two stores do not silently disagree about where time comes from.
- Anchors: `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt`,
  `petich-postgres/src/main/kotlin/ExposedIdempotencyRepository.kt`,
  `petich-core/src/commonMain/kotlin/Petich.kt`

## Closed 2026-09-17

Both Exposed stores take a `PetichClock`; the sqlx4k store was born taking one. The platform read
that remains lives in one internal function, `systemTimeMillis()`, carrying the module's single
`ktlint:kapkan:wall-clock` suppression — where before there were two suppressions in two files, and
with the native store there would have been a third answer to the same question.

**The default is the old behaviour**, so no consumer changes: `ExposedPetichRepository(db, table,
outboxTable)` still compiles and still stamps from the system clock. What the parameter buys is the
seam — an application running several replicas can hand them one source of time, which is the cheap
half of youndie/petich#20 without waiting for the column default that changes DDL this library does
not ship.

**Exercised, not just written.** Two tests construct the stores with `PetichClock { 4_242L }` and
read the column back. The value is deliberately absurd: a store that read the platform clock would
write something near 1.7e12, so it cannot pass by coincidence. Proved by mutation —
`clock.nowEpochMs()` swapped back for `systemTimeMillis()`:

```
ConformanceTest > the outbox stamp comes from the clock the store was given() FAILED
    expected: <[4242]> but was: <[1789627129493]>
```

**A trap worth recording, because it cost the item's own work.** The mutation and the change being
tested lived in the same file, and `git checkout -- <file>` to undo the mutation took the change
with it. The order that avoids it is: commit the change, then mutate, then restore. Nothing was
lost beyond a retype, and only because the diff was small.

**What this item does NOT do**, unchanged from what it said when it was written: it does not fix
#20. Several replicas still stamp `outbox_events.created_at` from their own clocks unless the
application gives them a shared one, and the ordering the relay reads still degrades with skew. The
fix is a server-side column default; this made the workaround available and the question single.
