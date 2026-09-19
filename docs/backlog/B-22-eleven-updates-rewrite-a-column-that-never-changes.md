---
id: B-22
title: "Every saga UPDATE rewrites the immutable payload column, and the tables are created with no room for HOT"
status: done
priority: P2
size: S
stage: stage-7-write-cost
---

# B-22 — the 11 writes cost more than they have to

Both stores set `payload` in every `UPDATE` (`ExposedPetichRepository.kt:92`,
`PostgresPetichStore.kt:95`), and `payload` is written once at creation and never changed by the
engine afterwards. It is also the largest column in the row. If it exceeds the TOAST threshold, each
of the 11 updates the README counts re-TOASTs it in full and leaves the old chunks behind for
autovacuum. `enriched_payload` is written unconditionally too, whether a step merged anything into
it or not.

Separately, neither `PetichTable` nor `petichPostgresSchema` says anything about `fillfactor`
(`Schema.kt:31`), so the tables are created at the default 100 and an update has no free space on
the page to land in. The index on `(status, suspended_until)` (`PetichTable.kt:46`) does not cost as
much as it looks: Postgres decides HOT by comparing the values of indexed columns, and the per-step
update changes neither of those two — status moves a handful of times per saga, not eleven.

- **`payload` leaves the UPDATE, and the corpus gets a rule for it.** "An update does not change
  the payload" is exactly the kind of invariant `petich-conformance` exists to hold both stores to;
  without the rule the two implementations are free to drift apart on it again.
- **`fillfactor` is stated, not described.** `Schema.kt` already carries its own argument for this:
  what the queries need must be stated as SQL, because an index living in prose is one a migration
  does not create. A fill factor is the same class of fact. On the native side it is a line; on the
  JVM side Exposed's `Table` cannot express it, so the generated DDL needs an
  `ALTER TABLE ... SET (fillfactor = 85)` beside it — equivalent to `WITH` on a table that is still
  empty, since the setting governs how new pages are filled.
- **The asymmetry gets written down** rather than smoothed over: one store states it in the SQL it
  hands the application, the other in a statement the application runs after the generator. A reader
  comparing the two should find the reason where the difference is.
- **Rejected: dropping the `(status, suspended_until)` index** to protect HOT. The sweeper's query
  runs on every tick against the busiest table in the system, and the index costs a broken HOT chain
  only on the few writes that actually move `status`.
- **Does not cover:** the number of writes itself. 17 per six-step saga is the price of the
  recoverability this engine sells, and `B-19` is about making that price buy something.

- AC: neither store sends `payload` in an update, held by a rule in the corpus that fails a store
  that does; both schemas state a fill factor on the saga table, with the JVM side's statement
  living next to the generated DDL; the README's Cost section still matches what
  `pg_stat_user_tables` reports.
- Anchors: `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt`,
  `petich-postgres/src/main/kotlin/PetichTable.kt`,
  `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/PostgresPetichStore.kt`,
  `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/Schema.kt`,
  `petich-conformance/src/commonMain/kotlin/io/github/youndie/petich/conformance/PetichStoreConformance.kt`

## Closed 2026-09-19

The payload is out of both `UPDATE` statements — bound only by the insert in the sqlx4k store, the
same way `type` already was — and the corpus has a rule that fails a store which sends it. Both
schemas state `fillfactor = 80`.

**Two older rules of the corpus used the payload as their witness**, and both had to be rewritten
rather than deleted: "every field lands" now changes a field an update is allowed to move, and "a
stale version changes nothing" witnesses the step index. The second one mattered — with the payload
immutable, a store that ignored the version predicate entirely would have left it unchanged too and
passed by accident. A witness that cannot move is not a witness.

**80 rather than the 85 this item was written with.** The number in the original review came with a
range and a reason (70–80 for a table updated this often); 85 was mine and came with neither. On a
row this shape the difference is small and the argument is not, so the one with the argument wins.

**Both statements are executed and read back from `pg_class`.** A fill factor that was never applied
looks exactly like one that was, and a function nobody calls always works: `tuningStatements()` would
otherwise have been a string in a KDoc. Mutated afterwards — removing the `WITH` clause fails the
native test, emptying `tuningStatements()` fails the Exposed one, and putting the payload back into
the update makes the corpus name the new rule.

**The asymmetry is stated where a reader meets it**, in the KDoc of `tuningStatements()` and in the
README: the native module hands over SQL, so the setting lives in its `CREATE TABLE`; Exposed's
`Table` cannot express a storage parameter, so on that side it is an `ALTER` beside the generated
DDL. On an empty table the two are equivalent; on one that already holds sagas the `ALTER` governs
new pages only, and the bloat already there needs a `VACUUM FULL` or `pg_repack` — the application's
call and its downtime.

**What the AC asked and this could not do.** "The README's Cost section still matches what
`pg_stat_user_tables` reports" cannot be ticked: nothing reproduces that measurement, and this change
narrows each write without changing how many there are. Rather than assert it from an armchair, the
gap is now [B-23](B-23-nothing-checks-the-write-count.md) — a counting repository rather than the
statistics collector, which is asynchronous and cumulative and would make a flaky test out of a
deterministic question.

**Not covered:** the outbox and schedule tables, whose rows are also updated under an index that
includes the column being changed — one or two writes per row rather than eleven, so the same
argument applies with a much smaller number attached. And `enriched_payload`, which is still written
on every update whether a step merged into it or not; unlike the payload it legitimately changes,
and making that conditional means two statements where there is now one.

**Where it ran:** `./gradlew build` on the Linux box, 308 tests, 0 failures, both tuning tests
against a real Postgres on the JVM and — for the native schema — on `linuxX64` as well.

