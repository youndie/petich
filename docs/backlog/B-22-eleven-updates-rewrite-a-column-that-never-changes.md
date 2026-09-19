---
id: B-22
title: "Every saga UPDATE rewrites the immutable payload column, and the tables are created with no room for HOT"
status: open
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
