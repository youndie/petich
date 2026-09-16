---
id: B-08
title: "Which database does a native consumer store sagas in — Postgres through sqlx4k, or SQLite?"
status: question
priority: P1
size: XS
stage: stage-3-storage
---

# B-08 — The question that decides the store's name and its SQL

There is no native consumer of petich today: konekt and shashki both take it from JVM builds, and no
other repository in the portfolio names an `io.github.youndie.petich` coordinate
([research §1.9](../research/research-native-port.md)). The driver is therefore not derivable from
anything here, and it decides three things at once — the module's coordinate, its SQL dialect, and
what the conformance run in [B-09](B-09-native-store-module.md) runs against.

- **Postgres through sqlx4k** keeps the semantics of the existing store: same dialect, same JSON
  columns, the corpus comparing like with like, and a native service can share a database with a JVM
  one mid-migration.
- **SQLite through sqlx4k** is what a small native service usually carries, needs no server, and is
  what the neighbouring timer library chose — for a consumer that existed. Its dialect differs where
  it matters most for this engine: no `SKIP LOCKED` (which petich does not use today either), and
  different behaviour under concurrent writers.
- **Why this is a question and not a decision.** A name chosen before the consumer is a name that
  starts lying when the second driver arrives; chronik renamed its store one day before release for
  exactly that reason. Whichever is picked, the module is named after the driver *and* the dialect —
  `petich-sqlx4k-postgres`, not `petich-sqlx4k`.
- **Does not cover:** both. One module per driver is a rule rather than a preference — two sqlx4k
  drivers in one native binary do not link (research D5).

- AC: the owner names a driver, or names the consumer that will; the answer is written into
  [research](../research/research-native-port.md) §3 as a decision with its date, and B-09 stops
  being blocked.
- Anchors: `petich-postgres/src/main/kotlin/` (the dialect being matched or left),
  `gradle/libs.versions.toml`

