---
id: B-23
title: "The write count the README sells the engine on is measured by nothing"
status: wip
priority: P3
size: S
stage: stage-7-write-cost
---

# B-23 — "about 17 database writes, 11 of them into the saga table"

The Cost section names a number taken once, by hand, through `pg_stat_user_tables`, for a scenario
it describes in half a sentence (`1 INSERT + one UPDATE per interceptor + 1 final, plus the
suspend/resume machinery`). Nothing reproduces it. Four items have since changed what the engine
writes — a rollback that records its attempts, a stamp on every row, a fingerprint on every write —
and the number stayed the same because none of them added a write, which is a fact nobody can
check without redoing the measurement by hand.

- **A count of repository calls is the honest guard**, not `pg_stat_user_tables`: the statistics
  collector is asynchronous and cumulative, so a test reading it is a test that fails on a busy
  machine. What the README is really claiming is the number of statements the engine issues, and a
  counting repository observes exactly that, deterministically, with no database at all.
- **The scenario has to be written down before it can be counted.** The number depends on how many
  interceptors, how many suspensions and how many phases are involved, and the sentence in the
  README names only part of that. Whatever the test fixes is what the README should then describe.
- **Rejected: deleting the number.** It is the most useful paragraph in that file for anyone
  deciding whether to adopt the engine — the point is to make it checkable, not to stop saying it.
- **Does not cover:** the cost of each write, which is [B-22](B-22-eleven-updates-rewrite-a-column-that-never-changes.md)
  and is done. This is about how many there are.

- AC: a test names a scenario, counts the writes the engine issues for it, and fails when that count
  changes; the README's sentence describes the same scenario as the test, in the same terms.
- Anchors: `README.md`, `petich-core/src/commonTest/kotlin/`,
  `petich-core/src/commonMain/kotlin/Petich.kt`
