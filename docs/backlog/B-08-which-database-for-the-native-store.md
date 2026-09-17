---
id: B-08
title: "Which database does a native consumer store sagas in — Postgres through sqlx4k, or SQLite?"
status: done
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

## Answered 2026-09-17 — Postgres through sqlx4k

The owner picked Postgres. So the module is **`petich-sqlx4k-postgres`** — named after the driver
*and* the dialect, because the day a second sqlx4k backend appears next to it a name like
`petich-sqlx4k` starts lying, and chronik renamed its own store one day before release for exactly
that.

What the answer buys, beyond having one: the corpus compares like with like. The SQL this store
writes is the same dialect the Exposed store writes, so a rule that both satisfy is a rule about
petich rather than about what two different databases happen to share — and a rule only one
satisfies is a real difference with a name. It also means a native service and a JVM service can
share a database through a migration, which a SQLite store would have made impossible.

What it costs, said plainly: the tests need a **server**. SQLite would have been a file; this needs
a Postgres reachable from both `jvmTest` and `linuxX64Test`, and [B-09](B-09-native-store-module.md)
carries how that is arranged rather than leaving each contributor to invent it.
