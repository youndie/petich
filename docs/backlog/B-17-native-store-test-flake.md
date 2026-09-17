---
id: B-17
title: "The native store's concurrency test fails sometimes, with an I/O error from the driver"
status: wip
priority: P1
size: S/M
stage: stage-3-storage
---

# B-17 — `Io :: Unexpected error occurred`, on linuxX64, under competition

`ConcurrentWritersTest > four writers racing to advance one saga leave exactly one winner` failed on
a hosted runner in the `[linuxX64]` variant, inside a run of the ordinary gate:

```
io.github.youndie.petich.sqlx4k.postgres.ConcurrentWritersTest.four writers racing to advance one
saga leave exactly one winner[linuxX64] FAILED
    Io :: Unexpected error occurred.
> Task :petich-sqlx4k-postgres:allTests FAILED
```

The same message had been seen once before, against a Postgres started by hand on another port
while `psql` on the same URL worked — and was recorded in
[B-09](B-09-native-store-module.md) as *unexplained rather than papered over*. It is now clear that
recording it as an oddity of one container was wrong: it is a flake, it reaches CI, and it belongs
to this repository until proved otherwise.

**What is known, and what is only suspected.**

| Observation | Where |
|---|---|
| fails | a 2-core hosted runner, `[linuxX64]`, once in the runs so far |
| fails | a container started by hand seconds earlier, while `psql` answered on the same URL |
| passes | five consecutive `--rerun-tasks` runs on a 20-core box |
| passes | every `jvmTest` run of the same suite, so far |

So it is timing-sensitive and it has only ever been seen on the native driver, which is Rust and has
its own runtime. That is a correlation, not a mechanism, and this item does not claim one.

- **The first fix is ours and is not a guess.** The Gradle task that starts the container asked
  `docker exec … pg_isready` — readiness from INSIDE, which says nothing about the published port
  the tests connect to. Between "ready inside" and "the mapping accepts" there is a window, and a
  driver connecting into it fails with an I/O error rather than with "the database is not up".
  Readiness is now checked from the host, through the same port: a TCP connect, then `pg_isready`
  over it.
- **That closes our half and may not close the flake.** A fix without a reproduction is a
  hypothesis. What settles it is runs: the table above grows with every occurrence, and the item
  stays `wip` until the gate has been green across enough of them to make the old rate unlikely.
- **What must NOT be done meanwhile:** a retry around the test, a longer timeout, or moving the case
  to jvm-only. Each of those makes the report stop rather than the defect, and the defect here would
  be one a consumer meets under load — four writers is the shape of a busy service, not of a test.

- AC: the gate is green across at least twenty consecutive runs of `linuxX64Test` on a runner of the
  class that failed (two cores), and the occurrence table above says so; or the mechanism is found,
  named here, and fixed.
- Anchors: `petich-sqlx4k-postgres/build.gradle.kts`,
  `petich-sqlx4k-postgres/src/commonTest/kotlin/io/github/youndie/petich/sqlx4k/postgres/ConcurrentWritersTest.kt`,
  `petich-sqlx4k-postgres/src/commonTest/kotlin/io/github/youndie/petich/sqlx4k/postgres/PostgresHarness.kt`
