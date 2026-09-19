---
id: B-24
title: "A release that adds a column to the saga table names it nowhere, and the consumer learns it at runtime"
status: done
priority: P0
size: S
stage: stage-8-upgrade
---

# B-24 — three columns arrived and nothing told anyone

0.3.0 adds `compensation_attempts`, `updated_at` and `chain_fingerprint` to the saga table. petich
ships no DDL, by a decision that is not being reopened here — but shipping no DDL only works if the
release says which statements a consumer has to write. Nothing does: not the README, not the module
documents, not a changelog, because there is no changelog.

**The rehearsal on `0.3.0.56` is the evidence.** Both real consumers keep the schema by hand —
konekt by Flyway migrations, on its own decision not to use `SchemaUtils.create`, and shashki by
`V1__petich.sql`. Pointed at the snapshot, konekt failed thirteen saga tests, every one of them with
`ERROR: column petiches.compensation_attempts does not exist`, and shashki's own schema guard
reported the same absence in better words. Neither failure names petich, a version, or a statement
to run.

- **The fix is a section that says what to run, and a guard that fails when it goes stale.** A
  hand-written list beside a growing set of columns rots on the first release nobody remembers to
  edit, and the rot is invisible until a consumer hits it — which is exactly the shape this item is
  about. `tools/module-table-audit.py` is the precedent: a script compares the README's table against
  the build scripts, and it exists because that table rotted once already.
- **Rejected: shipping migrations.** That is the decision petich already took, and it holds — a
  library that owns the schema's lifecycle owns the consumer's deploy. What it does not license is
  silence about what changed.
- **Rejected: a CHANGELOG.md.** A file that grows by one section per release and is read by nobody
  at the moment it matters. The consumer's question is not "what changed" but "what do I run", and
  the answer belongs where they already look for DDL: next to the tables that describe themselves.
- **Does not cover:** the lock these statements take, which is
  [B-25](B-25-the-tuning-statement-takes-a-lock-it-does-not-mention.md).

- AC: a consumer upgrading from 0.2.0 finds the exact statements without reading a diff; a column
  added to `PetichTable` or to `petichPostgresSchema` without a line in that section fails a check
  that runs in the gate; the statements are the ones the rehearsal actually applied, not ones
  written from memory.
- Anchors: `README.md`, `petich-postgres/src/main/kotlin/PetichTable.kt`,
  `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/Schema.kt`,
  `tools/module-table-audit.py`

## Closed 2026-09-19

The README states the saga table column by column with the statement to run on a schema that
predates each one, and `tools/schema-notes-audit.py` in the gate compares **three** descriptions of
that table: the Exposed `PetichTable`, the SQL the native module hands over, and the README's own
row per column. The third is the one nobody would notice going stale; the first two also catch the
cheaper mistake of adding a column to one store and forgetting the other, which no test can see
until both run against one database.

**The guard compares the declarations, not only the names**, and that was a hole in its first
version. A README that names every column and gets one type wrong passes a set comparison and hands
a consumer an `ALTER` that builds a column too narrow for what the store writes — truncated data
rather than an error. The native schema spells each column in SQL and so does the README, so the two
texts are compared directly. Five mutations, all caught with the disagreement named: a column added
to `PetichTable` alone, a row deleted from the README, a column added to the native schema alone, a
type widened in the README, and a type changed in the schema.

**What it deliberately does not check** is the `since` column. Which release a column arrived in is
history, and a script guessing at history is worse than a person writing it down once.

**0.1.0 is called out rather than served.** Both real consumers are on it, and it predates this
repository's tags and the move to the current coordinates — there is nothing to diff against, so the
notes say to take the whole table instead of a delta rather than offering statements nobody can
verify.

**Where the statements come from.** They are the ones applied during the `0.3.0.56` rehearsal
against konekt and shashki, executed by Flyway against real Postgres in both, after which those
suites ran 590 and 468 tests green. They are not written from memory, and the type comparison above
is what keeps them true as the schema moves.

**Still not closed by this**, and left as a sentence rather than a new item: nothing re-runs those
`ALTER`s inside this repository. The corpus creates its tables fresh, so the upgrade path itself —
old table, three statements, a working store — is proved by the rehearsal and by the type
comparison, not by a test here.

