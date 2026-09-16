---
id: B-10
title: "The two wall-clock reads the second store cannot copy"
status: open
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

