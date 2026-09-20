---
id: B-34
title: "The two stores type the same column differently, and the audit cannot see it"
status: wip
priority: P1
size: S
stage: stage-9-definition
blocked_by: []
---

# B-34 — the README hands one DDL to two schemas that disagree

`PetichTable` declares the JSON-shaped columns with Exposed's `json()`, which Postgres creates as
`json`. `petichPostgresSchema()` spells the same columns `TEXT`. Both stores work on their own
database; the README's upgrade table gives a consumer **one** line per column, and that line is the
native store's spelling. An Exposed consumer that copies it builds a column whose type disagrees with
its own table declaration.

- **Found by a consumer, not by the suite.** konekt took `step_records TEXT NOT NULL DEFAULT '{}'`
  from the README into a Flyway migration, and its own schema guard reported one problem:
  `ALTER TABLE petiches ALTER COLUMN step_records SET DEFAULT '{}'::json`. The **default** it caught;
  the **type** it did not mention, because Exposed's migration statements compare defaults and not
  types. A consumer without konekt's guard gets no signal at all.
- **`tools/schema-notes-audit.py` cannot catch this, by its own construction.** It compares three
  descriptions by name, and declarations only between the native schema and the README — the two
  sources written in SQL. From `PetichTable` it extracts the column name and nothing else, because
  Exposed spells types in Kotlin. The audit is honest about comparing what is comparable, and the
  divergence lives precisely in the gap that honesty leaves. (Portfolio: *честное ограничение
  ослепило сторожа*.)
- **`step_records` is free to fix; `payload` and `enriched_payload` are not.** `step_records` arrives
  in 0.4.0 and no consumer has it yet except konekt's migration branch, so the native schema can be
  changed to `json` before anyone holds the other spelling. The payload columns have shipped since
  0.1.0 and a type change on them is a rewrite of the busiest table in a consumer's system — decide
  deliberately, do not fold it in here.
- **Not yet known: whether the mismatch is fatal or merely untidy.** Exposed writing a String into a
  `text` column, and sqlx4k reading a `json` column as text, may both work; nobody has run either.
  The item is not finished by an argument about drivers — run both stores against a database built
  the other one's way.

## Acceptance

- The native schema and `PetichTable` agree on `step_records`, or the README states per store what to
  run and the audit compares it.
- The audit fails when the two stores' spellings of one column disagree — including a column whose
  Exposed type is only expressible in Kotlin, or it says in its own docstring that it cannot and
  names what covers it instead.
- A conformance test writes a saga with one store and reads it with the other against a database
  created by each schema in turn, so "both work" stops being an assumption.
- The payload columns' divergence is recorded with a decision — changed, or kept with the reason.
